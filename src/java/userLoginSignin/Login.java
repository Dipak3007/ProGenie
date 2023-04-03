/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/JSP_Servlet/Servlet.java to edit this template
 */
package userLoginSignin;

import java.io.*;
import java.sql.*;
import javax.servlet.*;
import javax.servlet.http.*;

public class Login extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String email=request.getParameter("lemail");
        String pass=request.getParameter("lpass");
        HttpSession hs=request.getSession();
        RequestDispatcher dis;
        Connection con=null;
        
        try{
            Class.forName("com.mysql.cj.jdbc.Driver");
            con=DriverManager.getConnection("jdbc:mysql://localhost:3306/progenie?useSSL=false","root", "root");
            PreparedStatement ps=con.prepareStatement("select * from users where email=? and password=?");
            ps.setString(1, email);
            ps.setString(2, pass);
            ResultSet rs=ps.executeQuery();
            if(rs.next()){
                hs.setAttribute("name", rs.getString("uname"));
                hs.setAttribute("cname", rs.getString("name"));
                hs.setAttribute("email", rs.getString("email"));
                hs.setAttribute("call", rs.getString("phone"));
                dis=request.getRequestDispatcher("index.jsp");
            }
            else{
                request.setAttribute("status", "failed");
                dis=request.getRequestDispatcher("login.jsp");
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
