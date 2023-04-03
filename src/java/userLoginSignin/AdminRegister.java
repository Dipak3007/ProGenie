/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/JSP_Servlet/Servlet.java to edit this template
 */
package userLoginSignin;

import java.io.*;
import java.sql.*;
import javax.servlet.*;
import javax.servlet.http.*;

public class AdminRegister extends HttpServlet {
@Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String uname=request.getParameter("uname");
        String email=request.getParameter("email");
        String pro=request.getParameter("profession");
        String phone=request.getParameter("phone");
        String pass=request.getParameter("pass");
        RequestDispatcher dis=null;
        Connection con=null;

        try{
            Class.forName("com.mysql.cj.jdbc.Driver");
            con=DriverManager.getConnection("jdbc:mysql://localhost:3306/progenie?useSSL=false","root", "root");
            PreparedStatement ps=con.prepareStatement("insert into genie(uname,email,service,phone,password) values (?,?,?,?,?)");
            ps.setString(1, uname);
            ps.setString(2, email);
            ps.setString(3, pro);
            ps.setString(4, phone);
            ps.setString(5, pass);
            int row=ps.executeUpdate();
            dis=request.getRequestDispatcher("admin-login.jsp");
            if(row>0)
                request.setAttribute("status", "success");
            else
                request.setAttribute("status", "failed");
        }
        catch(Exception e){
            e.printStackTrace();
        }
        finally{
            try{
                con.close();     
                dis.forward(request, response);
            }
            catch(Exception e){
                e.printStackTrace();
            }
        }
    }
}
