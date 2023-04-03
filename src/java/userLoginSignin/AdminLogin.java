/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/JSP_Servlet/Servlet.java to edit this template
 */
package userLoginSignin;

import java.io.*;
import java.sql.*;
import javax.servlet.*;
import javax.servlet.http.*;

public class AdminLogin extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String uname=request.getParameter("luname");
        String pass=request.getParameter("lpass");
        HttpSession hs=request.getSession();
        RequestDispatcher dis;
        Connection con=null;
        
        try{
            Class.forName("com.mysql.cj.jdbc.Driver");
            con=DriverManager.getConnection("jdbc:mysql://localhost:3306/progenie?useSSL=false","root", "root");
            PreparedStatement ps=con.prepareStatement("select * from genie where uname=? and password=?");
            ps.setString(1, uname);
            ps.setString(2, pass);
            ResultSet rs=ps.executeQuery();
            if(rs.next()){
                hs.setAttribute("name", rs.getString("uname"));
                dis=request.getRequestDispatcher("admin.jsp");
            }
            else{
                request.setAttribute("status", "failed");
                dis=request.getRequestDispatcher("admin-login.jsp");
            }
            dis.forward(request, response);
        }
        catch(Exception e){
            e.printStackTrace();
        }
        finally{
            try{
                con.close();
            }
            catch(Exception e){
                e.printStackTrace();
            }
        }
    }
}
